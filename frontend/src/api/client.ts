import type { AuthResponse } from "./types";

// Empty string in local dev: Vite's dev-server proxy makes /api same-origin
// as far as the browser's concerned (vite.config.ts), so no base URL is
// needed and no CORS story exists at all. Deployed builds (Vercel) set
// VITE_API_BASE_URL to the Render backend's public URL at build time —
// that's when SecurityConfig's CORS allowlist actually starts mattering.
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

const ACCESS_TOKEN_KEY = "kotanoba.accessToken";
const REFRESH_TOKEN_KEY = "kotanoba.refreshToken";

export function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

export function storeTokens(auth: AuthResponse): void {
  localStorage.setItem(ACCESS_TOKEN_KEY, auth.accessToken);
  localStorage.setItem(REFRESH_TOKEN_KEY, auth.refreshToken);
}

export function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function rawFetch(path: string, options: RequestInit, accessToken: string | null): Promise<Response> {
  const headers = new Headers(options.headers);
  headers.set("Content-Type", "application/json");
  if (accessToken) {
    headers.set("Authorization", `Bearer ${accessToken}`);
  }
  return fetch(`${BASE_URL}${path}`, { ...options, headers });
}

// Access tokens are short-lived (15 min, JwtProperties) — long enough that
// hitting expiry mid-session during normal use is a real, not hypothetical,
// case, not something to defer. One retry after a silent refresh; if the
// refresh token itself is dead, surface the original 401 and let the caller
// (App.tsx) redirect to login.
async function refreshAccessToken(): Promise<string | null> {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return null;
  }
  const response = await fetch(`${BASE_URL}/api/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refreshToken }),
  });
  if (!response.ok) {
    clearTokens();
    return null;
  }
  const auth: AuthResponse = await response.json();
  storeTokens(auth);
  return auth.accessToken;
}

export async function apiFetch<T>(path: string, options: RequestInit = {}): Promise<T> {
  let accessToken = getAccessToken();
  let response = await rawFetch(path, options, accessToken);

  if (response.status === 401 && accessToken) {
    accessToken = await refreshAccessToken();
    if (accessToken) {
      response = await rawFetch(path, options, accessToken);
    }
  }

  if (!response.ok) {
    throw new ApiError(response.status, await response.text());
  }

  if (response.status === 204 || response.headers.get("content-length") === "0") {
    return undefined as T;
  }
  return response.json();
}
