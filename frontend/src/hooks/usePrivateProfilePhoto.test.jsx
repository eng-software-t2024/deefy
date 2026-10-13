import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import api from "../services/api";
import { usePrivateProfilePhoto } from "./usePrivateProfilePhoto";

vi.mock("../services/api", () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

function PhotoHarness({
  identityKey = 7,
  legacyUrl = "https://legacy.example/avatar.png",
  enabled = true,
}) {
  const [uploadConfirmed, setUploadConfirmed] = useState(false);
  const {
    photoUrl,
    hasPhoto,
    hasPrivatePhoto,
    loadingPhoto,
    photoError,
    mutationsEnabled,
    loadPhoto,
    uploadPhoto,
    deletePhoto,
  } = usePrivateProfilePhoto({ identityKey, legacyUrl, enabled });

  return (
    <div>
      <span data-testid="profile-name">Synthetic User</span>
      <span data-testid="photo-url">{photoUrl || ""}</span>
      <span data-testid="has-photo">{hasPhoto ? "yes" : "no"}</span>
      <span data-testid="has-private-photo">{hasPrivatePhoto ? "yes" : "no"}</span>
      <span data-testid="loading-photo">{loadingPhoto ? "yes" : "no"}</span>
      <span data-testid="photo-error">{photoError || ""}</span>
      <span data-testid="upload-confirmed">{uploadConfirmed ? "yes" : "no"}</span>
      <button type="button" onClick={() => void loadPhoto().catch(() => {})}>reload</button>
      <button
        type="button"
        disabled={!mutationsEnabled}
        onClick={() => {
          const file = new File(["image"], "avatar.png", { type: "image/png" });
          void uploadPhoto(file)
            .then(() => setUploadConfirmed(true))
            .catch(() => {});
        }}
      >
        upload
      </button>
      <button
        type="button"
        disabled={!mutationsEnabled || !hasPrivatePhoto}
        onClick={() => void deletePhoto().catch(() => {})}
      >
        delete
      </button>
    </div>
  );
}

describe("usePrivateProfilePhoto", () => {
  let nextObjectUrl;

  beforeEach(() => {
    vi.clearAllMocks();
    nextObjectUrl = 0;
    Object.defineProperty(URL, "createObjectURL", {
      configurable: true,
      value: vi.fn(() => `blob:profile-photo-${++nextObjectUrl}`),
    });
    Object.defineProperty(URL, "revokeObjectURL", {
      configurable: true,
      value: vi.fn(),
    });
  });

  afterEach(() => {
    cleanup();
  });

  it("mantém a URL legada e não acessa o proxy quando a flag está desabilitada", async () => {
    render(<PhotoHarness enabled={false} />);

    expect(screen.getByTestId("photo-url").textContent).toBe("https://legacy.example/avatar.png");
    expect(screen.getByTestId("has-private-photo").textContent).toBe("no");
    expect(screen.getByRole("button", { name: "upload" }).disabled).toBe(true);
    expect(screen.getByRole("button", { name: "delete" }).disabled).toBe(true);
    expect(api.get).not.toHaveBeenCalled();
    expect(api.post).not.toHaveBeenCalled();
    expect(api.delete).not.toHaveBeenCalled();
  });

  it("prioriza o blob privado e revoga object URLs ao recarregar e desmontar", async () => {
    api.get
      .mockResolvedValueOnce({ data: new Blob(["first"], { type: "image/png" }) })
      .mockResolvedValueOnce({ data: new Blob(["second"], { type: "image/png" }) });

    const view = render(<PhotoHarness />);
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-1"));
    expect(screen.getByTestId("has-private-photo").textContent).toBe("yes");

    fireEvent.click(screen.getByRole("button", { name: "reload" }));
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-2"));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-1");

    view.unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-2");
  });

  it("usa fallback legado somente para 404 e distingue falha operacional", async () => {
    api.get
      .mockRejectedValueOnce({ status: 404 })
      .mockRejectedValueOnce({ status: 500 });

    render(<PhotoHarness />);
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));
    expect(screen.getByTestId("photo-url").textContent).toBe("https://legacy.example/avatar.png");
    expect(screen.getByTestId("photo-error").textContent).toBe("");

    fireEvent.click(screen.getByRole("button", { name: "reload" }));
    await waitFor(() => expect(screen.getByTestId("photo-error").textContent).toBe("operational"));
    expect(screen.getByTestId("photo-url").textContent).toBe("https://legacy.example/avatar.png");
  });

  it("usa o DTO de upload apenas como confirmação sem substituir o perfil", async () => {
    api.get
      .mockRejectedValueOnce({ status: 404 })
      .mockResolvedValueOnce({ data: new Blob(["uploaded"], { type: "image/png" }) });
    api.post.mockResolvedValueOnce({
      data: {
        available: true,
        contentType: "image/png",
        sizeBytes: 8,
        updatedAt: "2026-10-13T00:00:00Z",
      },
    });

    render(<PhotoHarness />);
    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(1));

    fireEvent.click(screen.getByRole("button", { name: "upload" }));

    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-1"));
    expect(screen.getByTestId("profile-name").textContent).toBe("Synthetic User");
    expect(screen.getByTestId("upload-confirmed").textContent).toBe("yes");
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][0]).toBe("/users/me/photo/upload");
    expect(api.post.mock.calls[0][1]).toBeInstanceOf(FormData);
    expect(api.post.mock.calls[0][1].get("file")).toBeInstanceOf(File);
  });

  it("aceita DELETE 204, limpa somente a foto privada e preserva o legado", async () => {
    api.get.mockResolvedValueOnce({
      data: new Blob(["current"], { type: "image/png" }),
    });
    api.delete.mockResolvedValueOnce({ status: 204, data: undefined });

    render(<PhotoHarness />);
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-1"));

    fireEvent.click(screen.getByRole("button", { name: "delete" }));

    await waitFor(() => expect(screen.getByTestId("has-private-photo").textContent).toBe("no"));
    expect(screen.getByTestId("photo-url").textContent).toBe("https://legacy.example/avatar.png");
    expect(screen.getByTestId("profile-name").textContent).toBe("Synthetic User");
    expect(api.delete).toHaveBeenCalledWith("/users/me/photo");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-1");
  });
});
