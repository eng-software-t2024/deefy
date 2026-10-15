package br.com.deefy.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Component
@ConfigurationProperties(prefix = "deefy.discord.playback")
public class DiscordPlaybackProperties {

    private static final String LEGACY_SUPABASE_HOST = "prqdlbsazlodierobswv.supabase.co";

    private String apiUrl = "";
    private String ffmpegExecutable = "ffmpeg";
    private String ffprobeExecutable = "ffprobe";
    private String cacheDirectory = "/var/cache/deefy-discord";
    private Duration cacheTtl = Duration.ofMinutes(60);
    private long cacheMaxBytes = 1024L * 1024L * 1024L;
    private long cacheMaxFileBytes = 100L * 1024L * 1024L;
    private Duration cacheCleanupInterval = Duration.ofMinutes(5);
    private Duration audioInitialBuffer = Duration.ofSeconds(10);
    private Duration audioInitialBufferTimeout = Duration.ofSeconds(15);
    private int audioDownloadAttempts = 3;
    private List<Duration> audioDownloadBackoff = List.of(
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10)
    );
    private Duration audioConnectTimeout = Duration.ofSeconds(10);
    private Duration audioReadTimeout = Duration.ofSeconds(20);
    private Duration decodeValidationTimeout = Duration.ofSeconds(20);
    private Duration decodeValidationSample = Duration.ofSeconds(15);

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public String getFfmpegExecutable() {
        return ffmpegExecutable;
    }

    public void setFfmpegExecutable(String ffmpegExecutable) {
        this.ffmpegExecutable = ffmpegExecutable;
    }

    public String getFfprobeExecutable() {
        return ffprobeExecutable;
    }

    public void setFfprobeExecutable(String ffprobeExecutable) {
        this.ffprobeExecutable = ffprobeExecutable;
    }

    public Path cachePath() {
        return Path.of(cacheDirectory);
    }

    public String getCacheDirectory() {
        return cacheDirectory;
    }

    public void setCacheDirectory(String cacheDirectory) {
        this.cacheDirectory = cacheDirectory;
    }

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public long getCacheMaxBytes() {
        return cacheMaxBytes;
    }

    public void setCacheMaxBytes(long cacheMaxBytes) {
        this.cacheMaxBytes = cacheMaxBytes;
    }

    public long getCacheMaxFileBytes() {
        return cacheMaxFileBytes;
    }

    public void setCacheMaxFileBytes(long cacheMaxFileBytes) {
        this.cacheMaxFileBytes = cacheMaxFileBytes;
    }

    public Duration getCacheCleanupInterval() {
        return cacheCleanupInterval;
    }

    public void setCacheCleanupInterval(Duration cacheCleanupInterval) {
        this.cacheCleanupInterval = cacheCleanupInterval;
    }

    public Duration getAudioInitialBuffer() {
        return audioInitialBuffer;
    }

    public void setAudioInitialBuffer(Duration audioInitialBuffer) {
        this.audioInitialBuffer = audioInitialBuffer;
    }

    public Duration getAudioInitialBufferTimeout() {
        return audioInitialBufferTimeout;
    }

    public void setAudioInitialBufferTimeout(Duration audioInitialBufferTimeout) {
        this.audioInitialBufferTimeout = audioInitialBufferTimeout;
    }

    int initialBufferFrames() {
        if (audioInitialBuffer == null || audioInitialBuffer.isZero() || audioInitialBuffer.isNegative()) {
            return 1;
        }
        long frames = (audioInitialBuffer.toNanos() * 50L + 999_999_999L) / 1_000_000_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, frames));
    }

    public int getAudioDownloadAttempts() {
        return audioDownloadAttempts;
    }

    public void setAudioDownloadAttempts(int audioDownloadAttempts) {
        this.audioDownloadAttempts = audioDownloadAttempts;
    }

    public List<Duration> getAudioDownloadBackoff() {
        return audioDownloadBackoff;
    }

    public void setAudioDownloadBackoff(List<Duration> audioDownloadBackoff) {
        this.audioDownloadBackoff = audioDownloadBackoff;
    }

    public Duration getAudioConnectTimeout() {
        return audioConnectTimeout;
    }

    public void setAudioConnectTimeout(Duration audioConnectTimeout) {
        this.audioConnectTimeout = audioConnectTimeout;
    }

    public Duration getAudioReadTimeout() {
        return audioReadTimeout;
    }

    public void setAudioReadTimeout(Duration audioReadTimeout) {
        this.audioReadTimeout = audioReadTimeout;
    }

    public Duration getDecodeValidationTimeout() {
        return decodeValidationTimeout;
    }

    public void setDecodeValidationTimeout(Duration decodeValidationTimeout) {
        this.decodeValidationTimeout = decodeValidationTimeout;
    }

    public Duration getDecodeValidationSample() {
        return decodeValidationSample;
    }

    public void setDecodeValidationSample(Duration decodeValidationSample) {
        this.decodeValidationSample = decodeValidationSample;
    }

    public boolean isSelfHostedAudioUrl(String candidate) {
        if (candidate == null || candidate.isBlank() || apiUrl == null || apiUrl.isBlank()) {
            return false;
        }

        try {
            URI expectedOrigin = URI.create(apiUrl.trim());
            URI audioUri = URI.create(candidate.trim());
            String expectedHost = normalizeHost(expectedOrigin);
            String audioHost = normalizeHost(audioUri);
            return isHttp(expectedOrigin)
                    && isHttp(audioUri)
                    && !LEGACY_SUPABASE_HOST.equals(audioHost)
                    && expectedOrigin.getUserInfo() == null
                    && audioUri.getUserInfo() == null
                    && audioUri.getRawQuery() == null
                    && audioUri.getRawFragment() == null
                    && expectedOrigin.getScheme().equalsIgnoreCase(audioUri.getScheme())
                    && Objects.equals(expectedHost, audioHost)
                    && effectivePort(expectedOrigin) == effectivePort(audioUri)
                    && audioUri.getPath() != null
                    && !audioUri.getPath().isBlank();
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean isHttp(URI uri) {
        return uri.isAbsolute()
                && uri.getHost() != null
                && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
    }

    private String normalizeHost(URI uri) {
        return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
