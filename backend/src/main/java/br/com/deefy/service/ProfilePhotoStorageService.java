package br.com.deefy.service;

import org.springframework.web.multipart.MultipartFile;

public interface ProfilePhotoStorageService {

    StoredProfilePhoto uploadProfilePhoto(Long ownerId, MultipartFile file);

    ProfilePhotoContent downloadProfilePhoto(Long ownerId, String bucket, String objectPath);

    void deleteProfilePhoto(Long ownerId, String bucket, String objectPath);

    void restoreProfilePhoto(Long ownerId, StoredProfilePhoto reference, byte[] bytes);

    record StoredProfilePhoto(
            String bucket,
            String objectPath,
            String contentType,
            long sizeBytes
    ) {
    }

    record ProfilePhotoContent(
            byte[] bytes,
            String contentType
    ) {
        public ProfilePhotoContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
