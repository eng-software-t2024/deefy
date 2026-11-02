package br.com.deefy.dto.response;

public record PlaylistShareResponseDTO(
        Long id,
        Long usuarioId,
        String permissao,
        String origem,
        Boolean ativo
) {
}
