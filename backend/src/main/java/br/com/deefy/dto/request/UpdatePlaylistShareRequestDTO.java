package br.com.deefy.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePlaylistShareRequestDTO(
        @NotBlank(message = "A permissao e obrigatoria")
        @Size(max = 30, message = "A permissao deve ter no maximo 30 caracteres")
        String permissao
) {
}
