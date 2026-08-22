import { createContext, useContext, useState, type ReactNode } from "react";
import * as authApi from "../api/auth";
import { clearTokens, getAccessToken, storeTokens } from "../api/client";

interface AuthContextValue {
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  register: (email: string, password: string) => Promise<void>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  // Presence of an access token is enough to render the authenticated app —
  // if it's actually expired, the first API call's 401 triggers
  // apiFetch's refresh-or-fail path, which redirects to login on failure.
  // No need to duplicate that expiry logic here too.
  const [isAuthenticated, setIsAuthenticated] = useState(() => getAccessToken() !== null);

  async function login(email: string, password: string) {
    storeTokens(await authApi.login(email, password));
    setIsAuthenticated(true);
  }

  async function register(email: string, password: string) {
    storeTokens(await authApi.register(email, password));
    setIsAuthenticated(true);
  }

  function logout() {
    clearTokens();
    setIsAuthenticated(false);
  }

  return (
    <AuthContext.Provider value={{ isAuthenticated, login, register, logout }}>{children}</AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within AuthProvider");
  }
  return context;
}
