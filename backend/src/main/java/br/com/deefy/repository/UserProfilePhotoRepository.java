package br.com.deefy.repository;

import br.com.deefy.model.UserProfilePhoto;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserProfilePhotoRepository extends JpaRepository<UserProfilePhoto, Long> {
}
