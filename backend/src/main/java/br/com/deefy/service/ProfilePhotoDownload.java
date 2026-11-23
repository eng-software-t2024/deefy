package br.com.deefy.service;

public record ProfilePhotoDownload(
        byte[] bytes,
        String contentType
) {
    public ProfilePhotoDownload {
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }
}
