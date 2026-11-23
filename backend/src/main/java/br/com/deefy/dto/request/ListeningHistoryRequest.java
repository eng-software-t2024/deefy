package br.com.deefy.dto.request;

import jakarta.validation.constraints.NotNull;

public record ListeningHistoryRequest(
        @NotNull
        Long musicId
) {
}
