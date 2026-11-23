package br.com.deefy.controller;

import br.com.deefy.dto.request.ListeningHistoryResponse;
import br.com.deefy.exception.HistoryNotAuthorizedException;
import br.com.deefy.exception.handler.GlobalExceptionHandler;
import br.com.deefy.model.ListeningHistory;
import br.com.deefy.service.ListeningHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Teste de contrato HTTP do historico: dono consultando o proprio
 * historico recebe 200; id de outro usuario recebe 403 (e nao 500).
 */
@ExtendWith(MockitoExtension.class)
class ListeningHistoryAccessTest {

    @Mock
    private ListeningHistoryService listeningHistoryService;

    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new ListeningHistoryController(listeningHistoryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void getHistory_Returns200_WhenRequestingOwnHistory() throws Exception {
        ListeningHistoryResponse response = new ListeningHistoryResponse(1L, 1L, 10L, LocalDateTime.now());
        when(listeningHistoryService.getHistoryByUserId(eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(response), PageRequest.of(0, 10), 1));

        mvc.perform(get("/api/v1/history/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].musicId").value(10));
    }

    @Test
    void getHistory_Returns403_WhenRequestingAnotherUserHistory() throws Exception {
        when(listeningHistoryService.getHistoryByUserId(eq(2L), any()))
                .thenThrow(new HistoryNotAuthorizedException("Você só pode consultar o seu próprio histórico"));

        mvc.perform(get("/api/v1/history/2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.messages[0]").value("Você só pode consultar o seu próprio histórico"));
    }

    @Test
    void getHistory_Returns404_WhenUserHasNoHistory() throws Exception {
        when(listeningHistoryService.getHistoryByUserId(eq(1L), any()))
                .thenThrow(new br.com.deefy.exception.HistoryNotFoundException("No listening history found for user with id: 1"));

        mvc.perform(get("/api/v1/history/1"))
                .andExpect(status().isNotFound());
    }
}
