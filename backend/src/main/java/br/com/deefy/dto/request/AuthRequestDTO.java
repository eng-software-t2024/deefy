package br.com.deefy.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record AuthRequestDTO (
        @NotBlank(message = "O palco precisa do seu e-mail!")
        @Email(message = "E-mail desafinado! Verifique o endereço.")
        String email,

        @NotBlank(message = "Entrada sem ingresso VIP! A senha é obrigatória.")
        String senha
){
}
