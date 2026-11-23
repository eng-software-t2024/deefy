package br.com.deefy.service;

import br.com.deefy.dto.request.ListeningHistoryRequest;
import br.com.deefy.dto.request.ListeningHistoryResponse;
import br.com.deefy.dto.response.MusicListResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ListeningHistoryService {

    ListeningHistoryResponse saveListeningHistory(ListeningHistoryRequest request);
    Page<ListeningHistoryResponse> getHistoryByUserId(Long userId, Pageable pageable);
    List<MusicListResponseDTO> getRecommendations();
}