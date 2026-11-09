import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import type { ReactNode } from "react";
import { IS_DEMO_AUTH } from "../../lib/env";
import { tokenStorage } from "../../lib/token";
import { userStorage } from "../../lib/session";
import { authService } from "../../lib/authService";
import { buildDemoUser } from "../../lib/demoAuth";
import type { AuthUser, RoleName } from "../../types/api";

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  isDemo: boolean;
  login: (email: string, password: string) => Promise<void>;
  demoLogin: (role: RoleName) => void;
  logout: () => void;
  updateProfile: (profile: AuthUser) => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

// Khôi phục phiên từ localStorage (không gọi API nên không phụ thuộc task nào khác).
const restoreSession = (): AuthUser | null => {
  const saved = userStorage.get();
  if (!saved) return null;
  if (!IS_DEMO_AUTH && !tokenStorage.exists()) return null;
  return saved;
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(restoreSession);
  const [loading] = useState(false);

  // apiClient báo 401 (token hết hạn/không hợp lệ) → đăng xuất
  useEffect(() => {
    const onUnauthorized = () => {
      userStorage.clear();
      setUser(null);
    };
    window.addEventListener("sd:unauthorized", onUnauthorized);
    return () => window.removeEventListener("sd:unauthorized", onUnauthorized);
  }, []);

  const login = useCallback(async (email: string, password: string) => {
    // authService.login đã lưu access + refresh token, trả về hồ sơ người dùng.
    const profile = await authService.login({ email, password });
    const loggedIn: AuthUser = {
      id: profile.id,
      email: profile.email,
      fullName: profile.fullName,
      role: profile.role,
    };
    userStorage.set(loggedIn);
    setUser(loggedIn);
  }, []);

  const demoLogin = useCallback((role: RoleName) => {
    const demoUser = buildDemoUser(role);
    userStorage.set(demoUser);
    setUser(demoUser);
  }, []);

  const logout = useCallback(() => {
    // Thu hồi refresh token ở backend (lỗi mạng cũng không chặn đăng xuất cục bộ).
    // authService.logout tự xóa token khi xong; giao diện đăng xuất ngay.
    void authService.logout().catch(() => undefined);
    userStorage.clear();
    setUser(null);
  }, []);

  const updateProfile = useCallback((profile: AuthUser) => {
    const updated: AuthUser = { id: profile.id, email: profile.email, fullName: profile.fullName, role: profile.role };
    userStorage.set(updated);
    setUser(updated);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, loading, isDemo: IS_DEMO_AUTH, login, demoLogin, logout, updateProfile }),
    [user, loading, login, demoLogin, logout, updateProfile],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth phải dùng bên trong <AuthProvider>");
  return ctx;
}
