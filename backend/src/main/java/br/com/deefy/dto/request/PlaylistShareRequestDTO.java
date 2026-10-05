package br.com.deefy.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlaylistShareRequestDTO(
        @NotBlank(message = "O email do usuario e obrigatorio")
        @Email(message = "O email informado e invalido")
        @Size(max = 100, message = "O email deve ter no maximo 100 caracteres")
        String email,

        @NotBlank(message = "A permissao e obrigatoria")
        @Size(max = 30, message = "A permissao deve ter no maximo 30 caracteres")
        String permissao
) {
}
