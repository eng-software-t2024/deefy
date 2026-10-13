import { useCallback, useEffect, useRef, useState } from "react";
import api from "../services/api";

export function usePrivateProfilePhoto(identityKey) {
  const [photoUrl, setPhotoUrl] = useState(null);
  const [loadingPhoto, setLoadingPhoto] = useState(false);
  const objectUrlRef = useRef(null);
  const requestSequenceRef = useRef(0);

  const replaceObjectUrl = useCallback((nextUrl) => {
    const previousUrl = objectUrlRef.current;
    if (previousUrl && previousUrl !== nextUrl) {
      URL.revokeObjectURL(previousUrl);
    }

    objectUrlRef.current = nextUrl;
    setPhotoUrl(nextUrl);
  }, []);

  const clearPhoto = useCallback(() => {
    requestSequenceRef.current += 1;
    replaceObjectUrl(null);
    setLoadingPhoto(false);
  }, [replaceObjectUrl]);

  const loadPhoto = useCallback(async () => {
    const requestSequence = ++requestSequenceRef.current;
    setLoadingPhoto(true);

    try {
      const response = await api.get("/users/me/photo", {
        responseType: "blob",
      });

      if (requestSequence !== requestSequenceRef.current) {
        return false;
      }

      const photoBlob = response.data;
      if (!(photoBlob instanceof Blob) || photoBlob.size === 0) {
        replaceObjectUrl(null);
        return false;
      }

      const nextUrl = URL.createObjectURL(photoBlob);
      if (requestSequence !== requestSequenceRef.current) {
        URL.revokeObjectURL(nextUrl);
        return false;
      }

      replaceObjectUrl(nextUrl);
      return true;
    } catch (error) {
      if (requestSequence !== requestSequenceRef.current) {
        return false;
      }

      if (error?.status === 404) {
        replaceObjectUrl(null);
        return false;
      }

      throw error;
    } finally {
      if (requestSequence === requestSequenceRef.current) {
        setLoadingPhoto(false);
      }
    }
  }, [replaceObjectUrl]);

  const uploadPhoto = useCallback(async (file) => {
    const formData = new FormData();
    formData.append("file", file);

    const response = await api.post("/users/me/photo/upload", formData);
    if (response.data?.available !== true) {
      throw new Error("A API não confirmou a disponibilidade da foto enviada.");
    }

    await loadPhoto();
    return response.data;
  }, [loadPhoto]);

  const deletePhoto = useCallback(async () => {
    await api.delete("/users/me/photo");
    clearPhoto();
  }, [clearPhoto]);

  useEffect(() => {
    clearPhoto();

    if (identityKey === null || identityKey === undefined) {
      return undefined;
    }

    loadPhoto().catch(() => {
      // 401/403 seguem o interceptor compartilhado; outros erros mantêm o avatar padrão.
    });

    return () => {
      requestSequenceRef.current += 1;
    };
  }, [clearPhoto, identityKey, loadPhoto]);

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
    loadingPhoto,
    loadPhoto,
    uploadPhoto,
    deletePhoto,
  };
}
