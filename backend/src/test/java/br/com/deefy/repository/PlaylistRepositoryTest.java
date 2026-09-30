package br.com.deefy.repository;

import br.com.deefy.model.Playlist;
import br.com.deefy.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PlaylistRepositoryTest {
    @Autowired TestEntityManager entityManager;
    @Autowired PlaylistRepository repository;

    private User user(String email) {
        User user = new User(); user.setNome(email); user.setEmail(email); user.setSenha("test");
        return entityManager.persist(user);
    }
    private Playlist playlist(User owner, boolean publica) {
        Playlist playlist = new Playlist(); playlist.setOwner(owner); playlist.setName("Playlist");
        playlist.setPublica(publica);
        return entityManager.persist(playlist);
    }
    @Test void filtersPublicOwnAndGlobalPlaylistsAndReflectsVisibilityChanges() {
        User a = user("a@test.com"), b = user("b@test.com"), system = user("system@test.com");
        Playlist publicA = playlist(a, true), privateA = playlist(a, false);
        Playlist publicB = playlist(b, true), privateB = playlist(b, false);
        Playlist global = playlist(system, true), privateSystem = playlist(system, false);
        entityManager.flush();
        assertThat(repository.findByPublicaTrueOrderByDataCriacaoDesc())
                .containsExactlyInAnyOrder(publicA, publicB, global);
        assertThat(repository.findPersonalByOwnerIdExcludingGlobalOwner(a.getId(), system.getEmail()))
                .containsExactlyInAnyOrder(publicA, privateA);
        assertThat(repository.findPersonalByOwnerIdExcludingGlobalOwner(b.getId(), system.getEmail()))
                .containsExactlyInAnyOrder(publicB, privateB);
        assertThat(repository.findGlobalPlaylists(system.getEmail())).containsExactly(global);
        assertThat(repository.findPersonalByOwnerIdExcludingGlobalOwner(system.getId(), system.getEmail()))
                .containsExactly(privateSystem);
        publicA.setPublica(false); entityManager.flush();
        assertThat(repository.findByPublicaTrueOrderByDataCriacaoDesc()).containsExactlyInAnyOrder(publicB, global);
    }
}
