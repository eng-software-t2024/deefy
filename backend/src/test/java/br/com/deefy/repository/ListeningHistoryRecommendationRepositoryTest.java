package br.com.deefy.repository;

import br.com.deefy.model.ListeningHistory;
import br.com.deefy.model.Music;
import br.com.deefy.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class ListeningHistoryRecommendationRepositoryTest {
    @Autowired TestEntityManager entityManager;
    @Autowired ListeningHistoryRepository historyRepository;
    @Autowired MusicRepository musicRepository;

    private User user(String email) {
        User user = new User(); user.setNome(email); user.setEmail(email); user.setSenha("test");
        return entityManager.persist(user);
    }
    private Music music(String title, String genre) {
        Music music = new Music(); music.setTitle(title); music.setGenre(genre);
        music.setDurationSeconds(180); music.setFileUrl("http://test/" + title);
        return entityManager.persist(music);
    }
    private void listen(User user, Music music, int times) {
        for (int i = 0; i < times; i++) {
            entityManager.persist(new ListeningHistory(user, music, LocalDateTime.now()));
        }
    }

    @Test void ranksGenresByPlaysAndSplitsUnplayedFromPlayed() {
        User user = user("u@test.com"), other = user("o@test.com");
        Music pop1 = music("pop1", "Pop"), pop2 = music("pop2", "Pop"), pop3 = music("pop3", "Pop");
        Music trap = music("trap", "Trap"), rock = music("rock", "Rock");
        listen(user, pop1, 2); listen(user, pop2, 1); listen(user, trap, 1);
        listen(other, rock, 3);
        entityManager.flush();

        assertThat(historyRepository.findTopGenresByUserId(user.getId(), PageRequest.of(0, 3)))
                .containsExactly("Pop", "Trap");
        assertThat(musicRepository.findUnplayedByGenres(List.of("Pop"), user.getId(), PageRequest.of(0, 10)))
                .containsExactly(pop3);
        assertThat(musicRepository.findPlayedByGenres(List.of("Pop"), user.getId(), PageRequest.of(0, 10)))
                .containsExactly(pop1, pop2);
    }

    @Test void userWithoutHistoryHasNoGenres() {
        User user = user("novo@test.com");
        music("pop1", "Pop");
        entityManager.flush();

        assertThat(historyRepository.findTopGenresByUserId(user.getId(), PageRequest.of(0, 3))).isEmpty();
    }
}
