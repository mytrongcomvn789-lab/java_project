import type { AuthUser } from "../types/api";

const USER_KEY = "sd_user";

// Lưu thông tin người dùng đang đăng nhập để tải lại trang vẫn giữ phiên.
export const userStorage = {
  get: (): AuthUser | null => {
    try {
      const raw = localStorage.getItem(USER_KEY);
      return raw ? (JSON.parse(raw) as AuthUser) : null;
    } catch {
      return null;
    }
  },
  set: (user: AuthUser) => localStorage.setItem(USER_KEY, JSON.stringify(user)),
  clear: () => localStorage.removeItem(USER_KEY),
};
