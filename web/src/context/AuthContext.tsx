import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { ApiError, login as apiLogin } from "../lib/api";

const TOKEN_STORAGE_KEY = "watchwire_token";

interface AuthContextValue {
  token: string | null;
  isAuthenticated: boolean;
  isLoggingIn: boolean;
  loginError: string | null;
  login: (username: string, password: string) => Promise<boolean>;
  logout: () => void;
}

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem(TOKEN_STORAGE_KEY));
  const [isLoggingIn, setIsLoggingIn] = useState(false);
  const [loginError, setLoginError] = useState<string | null>(null);

  const login = useCallback(async (username: string, password: string) => {
    setIsLoggingIn(true);
    setLoginError(null);
    try {
      const result = await apiLogin(username, password);
      localStorage.setItem(TOKEN_STORAGE_KEY, result.access_token);
      setToken(result.access_token);
      return true;
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "Login failed. Please try again.";
      setLoginError(message);
      return false;
    } finally {
      setIsLoggingIn(false);
    }
  }, []);

  const logout = useCallback(() => {
    localStorage.removeItem(TOKEN_STORAGE_KEY);
    setToken(null);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ token, isAuthenticated: token !== null, isLoggingIn, loginError, login, logout }),
    [token, isLoggingIn, loginError, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return ctx;
}
