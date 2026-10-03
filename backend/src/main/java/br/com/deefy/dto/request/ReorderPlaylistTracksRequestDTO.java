package br.com.deefy.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReorderPlaylistTracksRequestDTO(
        @NotEmpty(message = "A ordem das musicas e obrigatoria")
        @Valid
        List<@NotNull(message = "O ID da musica e obrigatorio") Long> musicIds
) {
}
