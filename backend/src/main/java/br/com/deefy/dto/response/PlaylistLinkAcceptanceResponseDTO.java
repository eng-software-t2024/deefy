package br.com.deefy.dto.response;

public record PlaylistLinkAcceptanceResponseDTO(
        Long playlistId,
        boolean aceito,
        String permissao,
        String origem,
        Boolean ativo
) {
}
