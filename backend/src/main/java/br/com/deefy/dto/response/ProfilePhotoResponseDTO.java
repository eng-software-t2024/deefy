package br.com.deefy.dto.response;

import java.time.Instant;

public record ProfilePhotoResponseDTO(
        boolean available,
        String contentType,
        long sizeBytes,
        Instant updatedAt
) {
}
