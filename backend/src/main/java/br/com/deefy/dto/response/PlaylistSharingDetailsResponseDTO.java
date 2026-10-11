package br.com.deefy.dto.response;

import java.util.List;
import java.util.UUID;

public record PlaylistSharingDetailsResponseDTO(
        Long proprietarioId,
        String proprietarioNome,
        String proprietarioEmail,
        boolean linkCompartilhamento,
        UUID tokenCompartilhamento,
        String permissaoLink,
        List<PlaylistShareDetailsResponseDTO> compartilhamentos
) {
}
