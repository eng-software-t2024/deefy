package br.com.deefy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SharedPlaylistRateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_REQUESTS = 60;
    private static final Duration WINDOW = Duration.ofMinutes(1);
    private static final String SHARED_PLAYLIST_PATH = "/api/v1/shared-playlists/";

    private final Map<String, RequestWindow> requests = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (!isPublicPlaylistLookup(request)) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientAddress(request) + ':' + request.getRequestURI();
        RequestWindow window = requests.compute(key, (ignored, current) -> {
            Instant now = Instant.now();
            if (current == null || current.startedAt.plus(WINDOW).isBefore(now)) {
                return new RequestWindow(now, 1);
            }
            return new RequestWindow(current.startedAt, current.count + 1);
        });

        if (window.count > MAX_REQUESTS) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(WINDOW.toSeconds()));
            response.setContentType("application/json");
            response.getWriter().write("{\"message\":\"Muitas tentativas. Tente novamente mais tarde.\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isPublicPlaylistLookup(HttpServletRequest request) {
        return "GET".equalsIgnoreCase(request.getMethod())
                && request.getRequestURI().startsWith(SHARED_PLAYLIST_PATH)
                && request.getRequestURI().length() == SHARED_PLAYLIST_PATH.length() + 36;
    }

    private String clientAddress(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private record RequestWindow(Instant startedAt, int count) {
    }
}
