package br.com.deefy.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "usuario_foto_perfil")
public class UserProfilePhoto {

    @Id
    @Column(name = "usuario_id", nullable = false)
    private Long userId;

    @Column(name = "bucket_id", nullable = false, columnDefinition = "TEXT")
    private String bucket;

    @Column(name = "object_path", nullable = false, unique = true, columnDefinition = "TEXT")
    private String objectPath;

    @Column(name = "content_type", nullable = false, columnDefinition = "TEXT")
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected UserProfilePhoto() {
    }

    public UserProfilePhoto(
            Long userId,
            String bucket,
            String objectPath,
            String contentType,
            long sizeBytes
    ) {
        this.userId = userId;
        replaceWith(bucket, objectPath, contentType, sizeBytes);
    }

    public void replaceWith(String bucket, String objectPath, String contentType, long sizeBytes) {
        this.bucket = bucket;
        this.objectPath = objectPath;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    @PreUpdate
    void updateTimestamp() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }

    public Long getUserId() {
        return userId;
    }

    public String getBucket() {
        return bucket;
    }

    public String getObjectPath() {
        return objectPath;
    }

    public String getContentType() {
        return contentType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
