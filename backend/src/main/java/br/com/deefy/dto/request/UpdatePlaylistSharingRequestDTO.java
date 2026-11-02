package br.com.deefy.dto.request;

import jakarta.validation.constraints.Size;

public record UpdatePlaylistSharingRequestDTO(
        boolean linkCompartilhamento,

        @Size(max = 6, message = "A permissao do link deve ter no maximo 6 caracteres")
        String permissaoLink
) {
}
