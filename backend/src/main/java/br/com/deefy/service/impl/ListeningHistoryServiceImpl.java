package br.com.deefy.service.impl;

import br.com.deefy.dto.request.ListeningHistoryRequest;
import br.com.deefy.dto.request.ListeningHistoryResponse;
import br.com.deefy.dto.response.MusicListResponseDTO;
import br.com.deefy.exception.HistoryNotFoundException;
import br.com.deefy.exception.MusicNotFoundException;
import br.com.deefy.exception.UsuarioNaoEncontradoException;
import br.com.deefy.mapper.ListeningHistoryMapper;
import br.com.deefy.mapper.MusicMapper;
import br.com.deefy.model.ListeningHistory;
import br.com.deefy.model.Music;
import br.com.deefy.model.User;
import br.com.deefy.repository.ListeningHistoryRepository;
import br.com.deefy.repository.MusicRepository;
import br.com.deefy.repository.UserRepository;
import br.com.deefy.service.ListeningHistoryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class ListeningHistoryServiceImpl implements ListeningHistoryService {

    private static final int TOP_GENRES_LIMIT = 3;

    @Value("${deefy.recommendations.size:12}")
    private int recommendationsSize;

    private final AuthenticatedUserService authenticatedUserService;
    private final ListeningHistoryRepository listeningHistoryRepository;
    private final UserRepository userRepository;
    private final MusicRepository musicRepository;
    private final ListeningHistoryMapper listeningHistoryMapper;
    private final MusicMapper musicMapper;

    public ListeningHistoryServiceImpl(AuthenticatedUserService authenticatedUserService, ListeningHistoryRepository listeningHistoryRepository, UserRepository userRepository, MusicRepository musicRepository, ListeningHistoryMapper listeningHistoryMapper, MusicMapper musicMapper) {
        this.authenticatedUserService = authenticatedUserService;
        this.listeningHistoryRepository = listeningHistoryRepository;
        this.userRepository = userRepository;
        this.musicRepository = musicRepository;
        this.listeningHistoryMapper = listeningHistoryMapper;
        this.musicMapper = musicMapper;
    }

    @Override
    public ListeningHistoryResponse saveListeningHistory(ListeningHistoryRequest request) {
        Long userId = authenticatedUserService.getAuthenticatedUser().getId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsuarioNaoEncontradoException("User not found with id: " + userId));

        Music music = musicRepository.findById(request.musicId())
                .orElseThrow(() -> new MusicNotFoundException(request.musicId()));

        LocalDateTime now = LocalDateTime.now();

        ListeningHistory saved = listeningHistoryMapper.toEntity(request);
        saved.setUser(user);
        saved.setMusic(music);
        saved.setDataHoraExecucao(now);

        ListeningHistory response = listeningHistoryRepository.save(saved);

        return listeningHistoryMapper.toResponse(response);
    }


    @Override
    public Page<ListeningHistoryResponse> getHistoryByUserId(Long userId, Pageable pageable) {
        if  (!userRepository.existsById(userId)) {
            throw new UsuarioNaoEncontradoException("User not found with id: " + userId);
        }

        Page<ListeningHistory> history = listeningHistoryRepository.findAllByUserIdOrderByDataHoraExecucaoDesc(userId, pageable);
        if (history.isEmpty()) {
            throw new HistoryNotFoundException("No listening history found for user with id: " + userId);
        }

        return history.map(listeningHistoryMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MusicListResponseDTO> getRecommendations() {
        Long userId = authenticatedUserService.getAuthenticatedUser().getId();

        List<String> genres = listeningHistoryRepository.findTopGenresByUserId(userId, PageRequest.of(0, TOP_GENRES_LIMIT));
        if (genres.isEmpty()) {
            return List.of();
        }

        List<Music> recommended = new ArrayList<>(
                musicRepository.findUnplayedByGenres(genres, userId, PageRequest.of(0, recommendationsSize)));

        int missing = recommendationsSize - recommended.size();
        if (missing > 0) {
            recommended.addAll(musicRepository.findPlayedByGenres(genres, userId, PageRequest.of(0, missing)));
        }

        return recommended.stream().map(musicMapper::toListDTO).toList();
    }
}
