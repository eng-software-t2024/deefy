import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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

function PhotoHarness({ identityKey = 7 }) {
  const {
    photoUrl,
    hasPhoto,
    loadingPhoto,
    loadPhoto,
    uploadPhoto,
    deletePhoto,
  } = usePrivateProfilePhoto(identityKey);

  return (
    <div>
      <span data-testid="photo-url">{photoUrl || ""}</span>
      <span data-testid="has-photo">{hasPhoto ? "yes" : "no"}</span>
      <span data-testid="loading-photo">{loadingPhoto ? "yes" : "no"}</span>
      <button type="button" onClick={() => void loadPhoto()}>reload</button>
      <button
        type="button"
        onClick={() => void uploadPhoto(new File(["image"], "avatar.png", { type: "image/png" }))}
      >
        upload
      </button>
      <button type="button" onClick={() => void deletePhoto()}>delete</button>
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

  it("trata 404 como ausência de foto sem repetir a requisição", async () => {
    api.get.mockRejectedValueOnce({ status: 404 });

    render(<PhotoHarness />);

    await waitFor(() => expect(screen.getByTestId("loading-photo").textContent).toBe("no"));
    expect(screen.getByTestId("has-photo").textContent).toBe("no");
    expect(api.get).toHaveBeenCalledTimes(1);
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it("revoga a URL anterior ao recarregar e a URL atual ao desmontar", async () => {
    api.get
      .mockResolvedValueOnce({ data: new Blob(["first"], { type: "image/png" }) })
      .mockResolvedValueOnce({ data: new Blob(["second"], { type: "image/png" }) });

    const view = render(<PhotoHarness />);
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-1"));
    expect(api.get).toHaveBeenNthCalledWith(1, "/users/me/photo", {
      responseType: "blob",
    });

    fireEvent.click(screen.getByRole("button", { name: "reload" }));
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-2"));
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-1");

    view.unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-2");
  });

  it("revoga a foto anterior quando a identidade autenticada muda", async () => {
    api.get
      .mockResolvedValueOnce({ data: new Blob(["first-user"], { type: "image/png" }) })
      .mockRejectedValueOnce({ status: 404 });

    const view = render(<PhotoHarness identityKey={7} />);
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-1"));

    view.rerender(<PhotoHarness identityKey={8} />);

    await waitFor(() => expect(api.get).toHaveBeenCalledTimes(2));
    expect(screen.getByTestId("has-photo").textContent).toBe("no");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-1");
  });

  it("usa o DTO de upload apenas como confirmação e recarrega o blob privado", async () => {
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
    expect(api.post).toHaveBeenCalledTimes(1);
    expect(api.post.mock.calls[0][0]).toBe("/users/me/photo/upload");
    expect(api.post.mock.calls[0][1]).toBeInstanceOf(FormData);
    expect(api.post.mock.calls[0][1].get("file")).toBeInstanceOf(File);
  });

  it("aceita DELETE 204 sem corpo, limpa o estado e revoga o blob", async () => {
    api.get.mockResolvedValueOnce({
      data: new Blob(["current"], { type: "image/png" }),
    });
    api.delete.mockResolvedValueOnce({ status: 204, data: undefined });

    render(<PhotoHarness />);
    await waitFor(() => expect(screen.getByTestId("photo-url").textContent).toBe("blob:profile-photo-1"));

    fireEvent.click(screen.getByRole("button", { name: "delete" }));

    await waitFor(() => expect(screen.getByTestId("has-photo").textContent).toBe("no"));
    expect(api.delete).toHaveBeenCalledWith("/users/me/photo");
    expect(URL.revokeObjectURL).toHaveBeenCalledWith("blob:profile-photo-1");
  });
});
