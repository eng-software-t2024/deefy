package br.com.deefy.discord;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DiscordAudioValidatorTest {

    @Test
    void shouldAcceptProbeMetadataAndRunLimitedDecodeSample() throws Exception {
        Path directory = Files.createTempDirectory("deefy-validator-fixture-");
        Path ffprobe = script(directory, "ffprobe", "#!/bin/sh\nprintf '%s' '{\"streams\":[{\"codec_type\":\"audio\",\"codec_name\":\"mp3\",\"sample_rate\":\"44100\",\"channels\":2,\"duration\":\"2.0\",\"bit_rate\":\"128000\"}],\"format\":{}}'\n");
        Path ffmpeg = script(directory, "ffmpeg", "#!/bin/sh\nexit 0\n");
        Path audio = directory.resolve("1.part");
        Files.write(audio, new byte[]{1, 2, 3});

        DiscordPlaybackProperties properties = new DiscordPlaybackProperties();
        properties.setFfprobeExecutable(ffprobe.toString());
        properties.setFfmpegExecutable(ffmpeg.toString());
        properties.setCacheMaxFileBytes(10);
        properties.setDecodeValidationTimeout(Duration.ofSeconds(2));
        properties.setDecodeValidationSample(Duration.ofSeconds(1));

        DiscordValidatedAudioMetadata result = new DiscordAudioValidator(properties, new ObjectMapper())
                .validate(audio);

        assertThat(result.codec()).isEqualTo("mp3");
        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.durationMillis()).isEqualTo(2000);
        assertThat(result.bitrate()).isEqualTo(128000L);
        assertThat(result.sha256()).hasSize(64);
    }

    @Test
    void shouldParseCompleteProbeJsonWhenItExceedsDiagnosticLimit() throws Exception {
        Path directory = Files.createTempDirectory("deefy-validator-large-probe-");
        String padding = "x".repeat(1024);
        String probeJson = "{\"streams\":[{\"codec_type\":\"audio\",\"codec_name\":\"mp3\","
                + "\"sample_rate\":\"44100\",\"channels\":2,\"duration\":\"2.0\","
                + "\"bit_rate\":\"128000\"}],\"format\":{\"comment\":\""
                + padding + "\"}}";
        Path ffprobe = script(directory, "ffprobe", "#!/bin/sh\nprintf '%s' '" + probeJson + "'\n");
        Path ffmpeg = script(directory, "ffmpeg", "#!/bin/sh\nexit 0\n");
        Path audio = directory.resolve("large-probe.part");
        Files.write(audio, new byte[]{1, 2, 3});

        DiscordPlaybackProperties properties = new DiscordPlaybackProperties();
        properties.setFfprobeExecutable(ffprobe.toString());
        properties.setFfmpegExecutable(ffmpeg.toString());
        properties.setCacheMaxFileBytes(10);

        DiscordValidatedAudioMetadata result = new DiscordAudioValidator(properties, new ObjectMapper())
                .validate(audio);

        assertThat(result.codec()).isEqualTo("mp3");
        assertThat(result.sampleRate()).isEqualTo(44100);
        assertThat(result.channels()).isEqualTo(2);
        assertThat(result.durationMillis()).isEqualTo(2000);
    }

    @Test
    void shouldRejectDecodeFailureWithoutExposingProcessDetails() throws Exception {
        Path directory = Files.createTempDirectory("deefy-validator-fixture-");
        Path ffprobe = script(directory, "ffprobe", "#!/bin/sh\nprintf '%s' '{\"streams\":[{\"codec_type\":\"audio\",\"codec_name\":\"mp3\",\"sample_rate\":\"44100\",\"channels\":2,\"duration\":\"2.0\"}]}'\n");
        Path ffmpeg = script(directory, "ffmpeg", "#!/bin/sh\nprintf '%s' 'decode failed https://private.invalid/audio?token=secret' >&2\nexit 1\n");
        Path audio = directory.resolve("2.part");
        Files.write(audio, new byte[]{1, 2, 3});

        DiscordPlaybackProperties properties = new DiscordPlaybackProperties();
        properties.setFfprobeExecutable(ffprobe.toString());
        properties.setFfmpegExecutable(ffmpeg.toString());
        properties.setCacheMaxFileBytes(10);

        assertThatThrownBy(() -> new DiscordAudioValidator(properties, new ObjectMapper()).validate(audio))
                .isInstanceOf(DiscordAudioPreparationException.class)
                .hasMessage("O áudio não pôde ser decodificado.");
    }

    @Test
    void shouldRejectProbeWithoutAudioStream() throws Exception {
        Path directory = Files.createTempDirectory("deefy-validator-fixture-");
        Path ffprobe = script(directory, "ffprobe", "#!/bin/sh\nprintf '%s' '{\"streams\":[{\"codec_type\":\"video\"}]}'\n");
        Path ffmpeg = script(directory, "ffmpeg", "#!/bin/sh\nexit 0\n");
        Path audio = directory.resolve("3.part");
        Files.write(audio, new byte[]{1, 2, 3});

        DiscordPlaybackProperties properties = new DiscordPlaybackProperties();
        properties.setFfprobeExecutable(ffprobe.toString());
        properties.setFfmpegExecutable(ffmpeg.toString());
        properties.setCacheMaxFileBytes(10);

        assertThatThrownBy(() -> new DiscordAudioValidator(properties, new ObjectMapper()).validate(audio))
                .isInstanceOf(DiscordAudioPreparationException.class)
                .hasMessage("O arquivo não contém áudio.");
    }

    private Path script(Path directory, String name, String contents) throws IOException {
        Path script = directory.resolve(name);
        Files.writeString(script, contents);
        Files.setPosixFilePermissions(script, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE
        ));
        return script;
    }
}
