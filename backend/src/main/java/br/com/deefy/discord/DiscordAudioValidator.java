package br.com.deefy.discord;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DiscordAudioValidator implements DiscordAudioValidatorPort {

    private static final Logger LOGGER = LoggerFactory.getLogger(DiscordAudioValidator.class);
    private static final int MAX_DIAGNOSTIC_LENGTH = 512;

    private final DiscordPlaybackProperties properties;
    private final ObjectMapper objectMapper;

    public DiscordAudioValidator(DiscordPlaybackProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public DiscordValidatedAudioMetadata validate(Path audioFile) {
        long started = System.nanoTime();
        if (audioFile == null || !Files.isRegularFile(audioFile)) {
            throw new DiscordAudioPreparationException("Arquivo de áudio indisponível.");
        }
        long size;
        try {
            size = Files.size(audioFile);
        } catch (IOException failure) {
            throw new DiscordAudioPreparationException("Não foi possível ler o áudio.", failure);
        }
        if (size <= 0 || size > properties.getCacheMaxFileBytes()) {
            throw new DiscordAudioPreparationException("O arquivo de áudio excede os limites permitidos.");
        }

        ProbeResult probe = probe(audioFile);
        decodeSample(audioFile);
        DiscordValidatedAudioMetadata metadata = new DiscordValidatedAudioMetadata(
                probe.codec(),
                probe.sampleRate(),
                probe.channels(),
                probe.durationMillis(),
                probe.bitrate(),
                size,
                sha256(audioFile)
        );
        LOGGER.info(
                "Discord audio validated: codec={}, sampleRate={}, channels={}, durationMs={}, bytes={}, validationMs={}",
                metadata.codec(),
                metadata.sampleRate(),
                metadata.channels(),
                metadata.durationMillis(),
                metadata.size(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        );
        return metadata;
    }

    private ProbeResult probe(Path audioFile) {
        ProcessResult result = run(List.of(
                properties.getFfprobeExecutable(),
                "-v", "error",
                "-print_format", "json",
                "-show_streams",
                "-show_format",
                audioFile.toString()
        ), properties.getDecodeValidationTimeout());
        if (result.exitCode() != 0) {
            throw new DiscordAudioPreparationException("O áudio não passou pela validação.");
        }
        try {
            JsonNode root = objectMapper.readTree(result.stdout());
            JsonNode audioStream = null;
            for (JsonNode stream : root.path("streams")) {
                if ("audio".equalsIgnoreCase(stream.path("codec_type").asText())) {
                    audioStream = stream;
                    break;
                }
            }
            if (audioStream == null) {
                throw new DiscordAudioPreparationException("O arquivo não contém áudio.");
            }
            String codec = audioStream.path("codec_name").asText("");
            int sampleRate = parseInt(audioStream.path("sample_rate"));
            int channels = audioStream.path("channels").asInt(0);
            long durationMillis = durationMillis(audioStream, root.path("format"));
            Long bitrate = optionalLong(audioStream, "bit_rate");
            if (codec.isBlank() || sampleRate <= 0 || channels <= 0 || durationMillis <= 0) {
                throw new DiscordAudioPreparationException("Os metadados do áudio são inválidos.");
            }
            return new ProbeResult(codec, sampleRate, channels, durationMillis, bitrate);
        } catch (IOException | IllegalArgumentException failure) {
            throw new DiscordAudioPreparationException("Não foi possível interpretar a validação do áudio.", failure);
        }
    }

    private void decodeSample(Path audioFile) {
        Duration sample = properties.getDecodeValidationSample();
        long sampleSeconds = Math.max(1, sample == null ? 1 : sample.toSeconds());
        ProcessResult result = run(List.of(
                properties.getFfmpegExecutable(),
                "-nostdin",
                "-hide_banner",
                "-v", "error",
                "-xerror",
                "-i", audioFile.toString(),
                "-map", "0:a:0",
                "-t", Long.toString(sampleSeconds),
                "-f", "null",
                "-"
        ), properties.getDecodeValidationTimeout());
        if (result.exitCode() != 0) {
            LOGGER.info("Discord audio validation failed: phase=decode, diagnostic={}",
                    sanitize(result.stderr()));
            throw new DiscordAudioPreparationException("O áudio não pôde ser decodificado.");
        }
    }

    private ProcessResult run(List<String> command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (IOException failure) {
            throw new DiscordAudioPreparationException("A validação de áudio não está disponível.", failure);
        }
        try {
            CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                    () -> readLimited(process.getInputStream()));
            CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                    () -> readLimited(process.getErrorStream()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new DiscordAudioPreparationException("A validação de áudio excedeu o tempo limite.");
            }
            return new ProcessResult(process.exitValue(), stdout.join(), stderr.join());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new DiscordAudioPreparationException("A validação de áudio foi interrompida.", failure);
        }
    }

    private String readLimited(InputStream stream) {
        byte[] buffer = new byte[MAX_DIAGNOSTIC_LENGTH];
        int captured = 0;
        try (stream) {
            int read;
            while ((read = stream.read(buffer, captured, buffer.length - captured)) >= 0) {
                captured += read;
                if (captured == buffer.length) {
                    byte[] discard = new byte[512];
                    while (stream.read(discard) >= 0) {
                        // Drain without retaining diagnostic content.
                    }
                    break;
                }
            }
        } catch (IOException ignored) {
            // Process termination may close the pipe concurrently.
        }
        return new String(buffer, 0, captured, StandardCharsets.UTF_8);
    }

    private String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
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
            throw new DiscordAudioPreparationException("Não foi possível verificar o áudio.", failure);
        }
    }

    private long durationMillis(JsonNode stream, JsonNode format) {
        String raw = stream.path("duration").asText("");
        if (raw.isBlank()) {
            raw = format.path("duration").asText("");
        }
        try {
            return raw.isBlank() ? 0 : Math.round(Double.parseDouble(raw) * 1000.0);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private int parseInt(JsonNode node) {
        try {
            return Integer.parseInt(node.asText("0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private Long optionalLong(JsonNode node, String field) {
        String value = node.path(field).asText("");
        try {
            return value.isBlank() ? null : Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String sanitize(String value) {
        String sanitized = DiscordFfmpegAudioSource.sanitizeDiagnostic(value);
        return sanitized.substring(0, Math.min(MAX_DIAGNOSTIC_LENGTH, sanitized.length()));
    }

    private record ProbeResult(String codec, int sampleRate, int channels, long durationMillis, Long bitrate) { }

    private record ProcessResult(int exitCode, String stdout, String stderr) { }
}
