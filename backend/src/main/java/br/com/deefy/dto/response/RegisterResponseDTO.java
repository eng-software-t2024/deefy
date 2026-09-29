package br.com.deefy.dto.response;

public record RegisterResponseDTO(
        String nome,
        String email,
        boolean cadastroPendenteAtualizado,
        String message
) {
}
