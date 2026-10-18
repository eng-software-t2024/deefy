package br.com.deefy.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePlaylistLinkPermissionRequestDTO(
        @NotBlank(message = "A permissao do link e obrigatoria")
        @Size(max = 6, message = "A permissao do link deve ter no maximo 6 caracteres")
        String permissaoLink
) {
}
