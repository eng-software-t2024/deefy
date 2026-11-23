package br.com.deefy.discord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Small, deterministic helpers for correlation values in diagnostic logs. */
final class DiscordDiagnosticSupport {

    private DiscordDiagnosticSupport() {
    }

    static String pseudonymize(Object value) {
        if (value == null || value.toString().isBlank()) {
            return "none";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(12);
            for (int index = 0; index < 6; index++) {
                result.append(String.format("%02x", digest[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }
}
