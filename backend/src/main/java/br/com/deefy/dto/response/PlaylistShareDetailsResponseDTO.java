package br.com.deefy.dto.response;

public record PlaylistShareDetailsResponseDTO(
        Long id,
        Long usuarioId,
        String usuarioNome,
        String usuarioEmail,
        String permissao,
        String origem,
        Boolean ativo
) {
}
