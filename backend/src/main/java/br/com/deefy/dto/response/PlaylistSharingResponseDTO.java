package br.com.deefy.dto.response;

import java.util.UUID;

public record PlaylistSharingResponseDTO(
        boolean linkCompartilhamento,
        UUID tokenCompartilhamento,
        String permissaoLink
) {
}
