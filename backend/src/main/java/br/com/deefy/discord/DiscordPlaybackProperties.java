package br.com.deefy.discord;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

@Component
@ConfigurationProperties(prefix = "deefy.discord.playback")
public class DiscordPlaybackProperties {

    private static final String LEGACY_SUPABASE_HOST = "prqdlbsazlodierobswv.supabase.co";

    private String apiUrl = "";
    private String ffmpegExecutable = "ffmpeg";

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
