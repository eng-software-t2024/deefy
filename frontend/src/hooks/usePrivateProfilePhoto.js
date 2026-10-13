import { useCallback, useEffect, useRef, useState } from "react";
import api from "../services/api";

export const PRIVATE_PROFILE_PHOTO_ENABLED =
  import.meta.env.VITE_PRIVATE_PROFILE_PHOTO_ENABLED === "true";

const normalizeLegacyUrl = (legacyUrl) => (
  typeof legacyUrl === "string" && legacyUrl.trim() ? legacyUrl.trim() : null
);

export function usePrivateProfilePhoto({
  identityKey,
  legacyUrl = null,
  enabled = false,
} = {}) {
  const normalizedLegacyUrl = normalizeLegacyUrl(legacyUrl);
  const [photoUrl, setPhotoUrl] = useState(normalizedLegacyUrl);
  const [hasPrivatePhoto, setHasPrivatePhoto] = useState(false);
  const [loadingPhoto, setLoadingPhoto] = useState(false);
  const [photoError, setPhotoError] = useState(null);
  const objectUrlRef = useRef(null);
  const legacyUrlRef = useRef(normalizedLegacyUrl);
  const legacySuppressedRef = useRef(false);
  const requestSequenceRef = useRef(0);

  const replacePhoto = useCallback((nextUrl, isPrivate) => {
    const previousObjectUrl = objectUrlRef.current;
    if (previousObjectUrl && previousObjectUrl !== nextUrl) {
      URL.revokeObjectURL(previousObjectUrl);
    }

    objectUrlRef.current = isPrivate ? nextUrl : null;
    setPhotoUrl(nextUrl);
    setHasPrivatePhoto(isPrivate);
  }, []);

  const showLegacyPhoto = useCallback(() => {
    const fallbackUrl = legacySuppressedRef.current ? null : legacyUrlRef.current;
    replacePhoto(fallbackUrl, false);
  }, [replacePhoto]);

  const loadPhoto = useCallback(async () => {
    if (!enabled || identityKey === null || identityKey === undefined) {
      setPhotoError(null);
      showLegacyPhoto();
      return false;
    }

    const requestSequence = ++requestSequenceRef.current;
    setLoadingPhoto(true);
    setPhotoError(null);

    try {
      const response = await api.get("/users/me/photo", {
        responseType: "blob",
      });

      if (requestSequence !== requestSequenceRef.current) {
        return false;
      }

      const photoBlob = response.data;
      if (!(photoBlob instanceof Blob) || photoBlob.size === 0) {
        throw new Error("A API retornou uma foto privada vazia ou inválida.");
      }

      const nextUrl = URL.createObjectURL(photoBlob);
      if (requestSequence !== requestSequenceRef.current) {
        URL.revokeObjectURL(nextUrl);
        return false;
      }

      replacePhoto(nextUrl, true);
      return true;
    } catch (error) {
      if (requestSequence !== requestSequenceRef.current) {
        return false;
      }

      if (error?.status === 404) {
        setPhotoError(null);
        showLegacyPhoto();
        return false;
      }

      setPhotoError("operational");
      throw error;
    } finally {
      if (requestSequence === requestSequenceRef.current) {
        setLoadingPhoto(false);
      }
    }
  }, [enabled, identityKey, replacePhoto, showLegacyPhoto]);

  const uploadPhoto = useCallback(async (file) => {
    if (!enabled) {
      throw new Error("A foto de perfil privada ainda não está habilitada.");
    }

    const formData = new FormData();
    formData.append("file", file);

    const response = await api.post("/users/me/photo/upload", formData);
    if (response.data?.available !== true) {
      throw new Error("A API não confirmou a disponibilidade da foto enviada.");
    }

    // O backend elimina a referência legada quando uma nova foto privada é salva.
    // Evita que um valor antigo reapareça após a mutação nesta sessão.
    legacySuppressedRef.current = true;
    await loadPhoto();
    return response.data;
  }, [enabled, loadPhoto]);

  const deletePhoto = useCallback(async () => {
    if (!enabled) {
      throw new Error("A foto de perfil privada ainda não está habilitada.");
    }
    if (!hasPrivatePhoto) {
      throw new Error("Não existe foto privada para remover.");
    }

    await api.delete("/users/me/photo");
    requestSequenceRef.current += 1;
    showLegacyPhoto();
    setPhotoError(null);
    setLoadingPhoto(false);
  }, [enabled, hasPrivatePhoto, showLegacyPhoto]);

  useEffect(() => {
    requestSequenceRef.current += 1;
    legacyUrlRef.current = normalizedLegacyUrl;
    legacySuppressedRef.current = false;
    setPhotoError(null);
    setLoadingPhoto(false);
    replacePhoto(normalizedLegacyUrl, false);

    if (!enabled || identityKey === null || identityKey === undefined) {
      return undefined;
    }

    loadPhoto().catch(() => {
      // O erro operacional fica disponível em photoError; o interceptor trata 401/403.
    });

    return () => {
      requestSequenceRef.current += 1;
    };
  }, [enabled, identityKey, loadPhoto, normalizedLegacyUrl, replacePhoto]);

  useEffect(() => () => {
    requestSequenceRef.current += 1;
    if (objectUrlRef.current) {
      URL.revokeObjectURL(objectUrlRef.current);
      objectUrlRef.current = null;
    }
  }, []);

  return {
    photoUrl,
    hasPhoto: Boolean(photoUrl),
    hasPrivatePhoto,
    loadingPhoto,
    photoError,
    mutationsEnabled: enabled,
    loadPhoto,
    uploadPhoto,
    deletePhoto,
  };
}
