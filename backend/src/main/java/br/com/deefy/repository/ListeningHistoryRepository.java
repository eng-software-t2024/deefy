package br.com.deefy.repository;

import br.com.deefy.model.ListeningHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;


public interface ListeningHistoryRepository extends JpaRepository<ListeningHistory, Long> {

    Page<ListeningHistory> findAllByUserIdOrderByDataHoraExecucaoDesc(Long userId, Pageable pageable);

    @Query("SELECT h.music.genre FROM ListeningHistory h WHERE h.user.id = :userId " +
           "GROUP BY h.music.genre ORDER BY COUNT(h) DESC")
    List<String> findTopGenresByUserId(@Param("userId") Long userId, Pageable pageable);
}